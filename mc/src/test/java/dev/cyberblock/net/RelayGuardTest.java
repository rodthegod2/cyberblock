package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/** Megaplan section 1.6, every rule with a fake clock. */
class RelayGuardTest {
	private final AtomicLong now = new AtomicLong(1_000_000L);
	private static final IntPredicate NONE_ISSUED = id -> false;

	private RelayGuard guard() {
		return new RelayGuard(1.0, 160.0, this.now::get);
	}

	private static String ground(final int x, final int z, final int yb, final int yt) {
		return "{\"t\":\"ground\",\"c\":[" + x + "," + z + "," + yb + "," + yt + "]}";
	}

	private static boolean accepted(final RelayGuard.Verdict v) {
		return v.message() != null;
	}

	private static String log(final RelayGuard g) {
		return String.join("\n", g.drainLog("CbHost1"));
	}

	@Test
	void aHostsValidMessagePasses() {
		RelayGuard.Verdict v = this.guard().check(true, "ground", ground(1, 2, 60, 64), 0, 0, NONE_ISSUED);
		assertTrue(accepted(v));
		assertNull(v.kick());
		assertEquals("ground", v.message().get("t").getAsString());
	}

	@Test
	void unknownTypesAndLocalTypesAreDropped() {
		RelayGuard g = this.guard();
		for (String t : List.of("cmd", "cam", "key", "quest", "nonsense")) {
			assertFalse(accepted(g.check(true, t, "{\"t\":\"" + t + "\"}", 0, 0, NONE_ISSUED)), t);
		}

		this.now.addAndGet(5000);
		String log = log(g);
		assertTrue(log.contains("relay: CbHost1 dropped cmd x 1 (type)"), log);
		assertTrue(log.contains("relay: CbHost1 dropped cam x 1 (type)"), log);
	}

	@Test
	void everySection12UpTypeIsWhitelisted() {
		for (String t : List.of("hello", "ground", "unground", "clear", "state", "hstate", "aim", "dmg", "npcs", "mobdmg", "pcells", "unlead", "killed",
			"scaninfo", "hackresult", "hackspread", "stats", "terrain")) {
			assertTrue(RelayGuard.isUpType(t), t);
		}

		assertFalse(RelayGuard.isUpType("cmd"));
	}

	@Test
	void theInnerTMustMatchAndTheJsonMustParse() {
		RelayGuard g = this.guard();
		assertFalse(accepted(g.check(true, "ground", "{\"t\":\"unground\",\"c\":[]}", 0, 0, NONE_ISSUED)));
		assertFalse(accepted(g.check(true, "ground", "not json", 0, 0, NONE_ISSUED)));
		assertFalse(accepted(g.check(true, "ground", "[1,2]", 0, 0, NONE_ISSUED)));
		assertFalse(accepted(g.check(true, "ground", "{\"c\":[]}", 0, 0, NONE_ISSUED)));
		assertFalse(accepted(g.check(true, "ground", "{\"t\":{\"x\":1}}", 0, 0, NONE_ISSUED)));
		this.now.addAndGet(5000);
		String log = log(g);
		assertTrue(log.contains("dropped ground x 3 (t)"), log);
		assertTrue(log.contains("dropped ground x 2 (parse)"), log);
	}

	@Test
	void overTheSizeCapIsDropped() {
		RelayGuard g = this.guard();
		String big = "{\"t\":\"clear\",\"pad\":\"" + "x".repeat(Payloads.UP_CAP) + "\"}";
		assertFalse(accepted(g.check(true, "clear", big, 0, 0, NONE_ISSUED)));
		this.now.addAndGet(5000);
		assertTrue(log(g).contains("dropped clear x 1 (size)"));
	}

	@Test
	void theBucketAllowsTheBurstThenTheRate() {
		RelayGuard g = this.guard();
		int ok = 0;
		for (int i = 0; i < 100; i++) {
			ok += accepted(g.check(true, "stats", "{\"t\":\"stats\"}", 0, 0, NONE_ISSUED)) ? 1 : 0;
		}

		assertEquals(4, ok, "stats: burst 4");
		this.now.addAndGet(500);
		assertTrue(accepted(g.check(true, "stats", "{\"t\":\"stats\"}", 0, 0, NONE_ISSUED)), "2/s: one token after 0.5 s");
		assertFalse(accepted(g.check(true, "stats", "{\"t\":\"stats\"}", 0, 0, NONE_ISSUED)));
		this.now.addAndGet(5000);
		assertTrue(log(g).contains("dropped stats x 97 (rate)"));
	}

	@Test
	void bucketsArePerType() {
		RelayGuard g = this.guard();
		for (int i = 0; i < 4; i++) {
			g.check(true, "stats", "{\"t\":\"stats\"}", 0, 0, NONE_ISSUED);
		}

		assertFalse(accepted(g.check(true, "stats", "{\"t\":\"stats\"}", 0, 0, NONE_ISSUED)));
		assertTrue(accepted(g.check(true, "aim", "{\"t\":\"aim\",\"d\":1}", 0, 0, NONE_ISSUED)));
	}

	@Test
	void theRateMultiplierScalesEveryBucket() {
		RelayGuard g = new RelayGuard(2.0, 160.0, this.now::get);
		int ok = 0;
		for (int i = 0; i < 100; i++) {
			ok += accepted(g.check(true, "stats", "{\"t\":\"stats\"}", 0, 0, NONE_ISSUED)) ? 1 : 0;
		}

		assertEquals(8, ok);
	}

	@Test
	void groundSharesACellBudget() {
		RelayGuard g = this.guard();
		// 16 columns of 1000 cells: 16000, then 4000 more fit, the next 1000 do not
		for (int i = 0; i < 16; i++) {
			assertTrue(accepted(g.check(true, "ground", ground(i, 0, 0, 999), 0, 0, NONE_ISSUED)));
		}

		for (int i = 0; i < 4; i++) {
			assertTrue(accepted(g.check(true, "unground", "{\"t\":\"unground\",\"c\":[" + i + ",1,0,999]}", 0, 0, NONE_ISSUED)));
		}

		assertFalse(accepted(g.check(true, "ground", ground(30, 0, 0, 999), 0, 0, NONE_ISSUED)));
		this.now.addAndGet(100);
		assertTrue(accepted(g.check(true, "ground", ground(30, 0, 0, 999), 0, 0, NONE_ISSUED)), "2000 cells back after 0.1 s");
		this.now.addAndGet(5000);
		assertTrue(log(g).contains("dropped ground x 1 (cells)"));
	}

	@Test
	void farEntriesAreDroppedAndTheRestKept() {
		RelayGuard g = this.guard();
		assertFalse(accepted(g.check(true, "ground", "{\"t\":\"ground\",\"c\":[10,10,60,64,500,0,60,64,-100,-100,1,2]}", 0, 0, NONE_ISSUED)),
			"held while the position may catch up");
		this.now.addAndGet(RelayGuard.HOLD_MS);
		List<RelayGuard.Message> out = g.drain(true, 0, 0, NONE_ISSUED);
		assertEquals(1, out.size());
		assertEquals("[10,10,60,64,-100,-100,1,2]", out.get(0).m().get("c").toString());
		assertFalse(accepted(g.check(true, "ground", ground(161, 0, 0, 1), 0, 0, NONE_ISSUED)));
		this.now.addAndGet(RelayGuard.HOLD_MS);
		assertTrue(g.drain(true, 0, 0, NONE_ISSUED).isEmpty(), "nothing left: dropped");
		assertTrue(accepted(g.check(true, "ground", ground(1000, 1000, 0, 1), 1000, 1000, NONE_ISSUED)), "the radius is around the host's player");
		this.now.addAndGet(5000);
		assertTrue(log(g).contains("dropped ground x 2 (far)"));
	}

	/** Cyberblock On: the DLL's clear and whole ground near V arrive before Steve's movement packet that puts him at V. */
	@Test
	void farGroundWaitsForThePositionToCatchUpAndKeepsTheOrder() {
		RelayGuard g = this.guard();
		assertTrue(accepted(g.check(true, "clear", "{\"t\":\"clear\"}", 0, 0, NONE_ISSUED)));
		assertFalse(accepted(g.check(true, "ground", ground(1000, 1000, 60, 64), 0, 0, NONE_ISSUED)));
		assertFalse(accepted(g.check(true, "state", "{\"t\":\"state\",\"v\":1}", 0, 0, NONE_ISSUED)), "nothing overtakes a held ground");
		assertFalse(accepted(g.check(true, "unground", "{\"t\":\"unground\",\"c\":[1001,1000,62,64]}", 0, 0, NONE_ISSUED)));
		assertTrue(g.drain(true, 0, 0, NONE_ISSUED).isEmpty(), "still far, still in time");
		// one tick later the server has the movement packet
		this.now.addAndGet(50);
		List<RelayGuard.Message> out = g.drain(true, 1000, 1000, NONE_ISSUED);
		assertEquals(List.of("ground", "state", "unground"), out.stream().map(RelayGuard.Message::t).toList());
		assertEquals("[1000,1000,60,64]", out.get(0).m().get("c").toString(), "the whole ground");
		assertTrue(g.drain(true, 1000, 1000, NONE_ISSUED).isEmpty());
		assertTrue(accepted(g.check(true, "ground", ground(1002, 1000, 60, 64), 1000, 1000, NONE_ISSUED)), "near again: no wait");
		this.now.addAndGet(5000);
		assertFalse(log(g).contains("far"), "nothing was dropped");
	}

	@Test
	void stillFarAfterTheHoldIsDroppedAndTheRestFollows() {
		RelayGuard g = this.guard();
		g.check(true, "ground", ground(1000, 1000, 60, 64), 0, 0, NONE_ISSUED);
		g.check(true, "aim", "{\"t\":\"aim\",\"d\":1}", 0, 0, NONE_ISSUED);
		this.now.addAndGet(RelayGuard.HOLD_MS - 1);
		assertTrue(g.drain(true, 0, 0, NONE_ISSUED).isEmpty());
		this.now.addAndGet(1);
		assertEquals(List.of("aim"), g.drain(true, 0, 0, NONE_ISSUED).stream().map(RelayGuard.Message::t).toList());
		this.now.addAndGet(5000);
		assertTrue(log(g).contains("dropped ground x 1 (far)"));
	}

	@Test
	void losingTheHostRoleDiscardsWhatIsHeld() {
		RelayGuard g = this.guard();
		g.check(true, "ground", ground(1000, 1000, 60, 64), 0, 0, NONE_ISSUED);
		assertTrue(g.drain(false, 1000, 1000, NONE_ISSUED).isEmpty());
		assertTrue(g.drain(true, 1000, 1000, NONE_ISSUED).isEmpty(), "nothing left to release");
	}

	@Test
	void theHoldQueueIsCapped() {
		RelayGuard g = new RelayGuard(100.0, 160.0, this.now::get);
		g.check(true, "ground", ground(1000, 1000, 60, 64), 0, 0, NONE_ISSUED);
		int released = 0;
		for (int i = 0; i < RelayGuard.HOLD_CAP; i++) {
			released += g.check(true, "aim", "{\"t\":\"aim\",\"d\":1}", 0, 0, NONE_ISSUED).messages().size();
		}

		assertEquals(RelayGuard.HOLD_CAP, released, "past the cap the oldest is checked as it stands, the rest follow");
	}

	@Test
	void terrainHasTheWideRadius() {
		RelayGuard g = this.guard();
		// cell 150 is x 300..302: inside 400, outside 160
		RelayGuard.Verdict v = g.check(true, "terrain", "{\"t\":\"terrain\",\"c\":[150,0,64,0,250,0,64,0]}", 0, 0, NONE_ISSUED);
		assertTrue(accepted(v));
		assertEquals("[150,0,64,0]", v.message().get("c").toString());
	}

	@Test
	void npcsPcellsDmgAndScaninfoPositions() {
		RelayGuard g = this.guard();
		RelayGuard.Verdict npcs = g.check(true, "npcs",
			"{\"t\":\"npcs\",\"list\":[{\"id\":\"1\",\"p\":[1,64,1]},{\"id\":\"2\",\"p\":[900,64,0]}]}", 0, 0, NONE_ISSUED);
		assertEquals(1, npcs.message().getAsJsonArray("list").size());
		assertTrue(accepted(g.check(true, "npcs", "{\"t\":\"npcs\",\"list\":[]}", 0, 0, NONE_ISSUED)), "an empty list is a real 'all gone'");
		assertFalse(accepted(g.check(true, "npcs", "{\"t\":\"npcs\",\"list\":[{\"id\":\"2\",\"p\":[900,64,0]}]}", 0, 0, NONE_ISSUED)),
			"a list that only held far entries is not turned into 'all gone'");
		RelayGuard.Verdict pcells = g.check(true, "pcells", "{\"t\":\"pcells\",\"c\":[1,64,1,900,64,0]}", 0, 0, NONE_ISSUED);
		assertEquals("[1,64,1]", pcells.message().get("c").toString());
		RelayGuard.Verdict dmg = g.check(true, "dmg", "{\"t\":\"dmg\",\"frac\":0.2,\"kind\":\"bullet\",\"from\":[900,64,0]}", 0, 0, NONE_ISSUED);
		assertTrue(accepted(dmg), "the damage stays");
		assertTrue(dmg.message().get("from").isJsonNull(), "a far 'from' is dropped");
		int id = 7;
		assertFalse(accepted(g.check(true, "scaninfo", "{\"t\":\"scaninfo\",\"id\":7,\"p\":[900,64,0]}", 0, 0, n -> n == id)));
		assertTrue(accepted(g.check(true, "scaninfo", "{\"t\":\"scaninfo\",\"id\":7,\"p\":[9,64,0]}", 0, 0, n -> n == id)));
	}

	@Test
	void malformedPositionsAreInvalid() {
		RelayGuard g = this.guard();
		assertFalse(accepted(g.check(true, "ground", "{\"t\":\"ground\",\"c\":[1,2,3]}", 0, 0, NONE_ISSUED)), "not quadruples");
		assertFalse(accepted(g.check(true, "ground", "{\"t\":\"ground\",\"c\":[1,2,9,3]}", 0, 0, NONE_ISSUED)), "inverted");
		assertFalse(accepted(g.check(true, "ground", "{\"t\":\"ground\",\"c\":[1.5,2,3,4]}", 0, 0, NONE_ISSUED)), "not whole");
		assertFalse(accepted(g.check(true, "npcs", "{\"t\":\"npcs\",\"list\":[{\"p\":[1,2]}]}", 0, 0, NONE_ISSUED)));
		this.now.addAndGet(5000);
		assertTrue(log(g).contains("dropped ground x 3 (invalid)"));
	}

	@Test
	void repliesNeedAnIssuedRequestId() {
		RelayGuard g = this.guard();
		IntPredicate issued = n -> n == 8;
		assertTrue(accepted(g.check(true, "hackresult", "{\"t\":\"hackresult\",\"id\":8,\"ok\":true,\"reason\":\"\"}", 0, 0, issued)));
		assertFalse(accepted(g.check(true, "hackresult", "{\"t\":\"hackresult\",\"id\":9,\"ok\":true,\"reason\":\"\"}", 0, 0, issued)));
		assertFalse(accepted(g.check(true, "hackspread", "{\"t\":\"hackspread\",\"target\":\"1\"}", 0, 0, issued)));
		assertFalse(accepted(g.check(true, "scaninfo", "{\"t\":\"scaninfo\",\"id\":\"8\"}", 0, 0, issued)));
		assertFalse(accepted(g.check(true, "hackresult", "{\"t\":\"hackresult\",\"id\":8.5}", 0, 0, issued)));
		this.now.addAndGet(5000);
		String log = log(g);
		assertTrue(log.contains("dropped hackresult x 2 (id)"), log);
	}

	@Test
	void guestsCanNeverSendUpAnd50InAMinuteKick() {
		RelayGuard g = this.guard();
		for (int i = 0; i < 49; i++) {
			RelayGuard.Verdict v = g.check(false, "ground", ground(0, 0, 0, 1), 0, 0, NONE_ISSUED);
			assertNull(v.message());
			assertNull(v.kick(), "drop " + (i + 1));
			this.now.addAndGet(1000);
		}

		// the first drops are now more than a minute old
		this.now.addAndGet(20_000);
		assertNull(g.check(false, "ground", ground(0, 0, 0, 1), 0, 0, NONE_ISSUED).kick(), "drops older than a minute do not count");
		RelayGuard fresh = this.guard();
		String kick = null;
		for (int i = 0; i < 50 && kick == null; i++) {
			kick = fresh.check(false, "hello", "{\"t\":\"hello\"}", 0, 0, NONE_ISSUED).kick();
		}

		assertEquals(RelayGuard.KICK, kick);
		this.now.addAndGet(5000);
		assertTrue(log(fresh).contains("relay: CbHost1 dropped hello x 50 (guest)"));
	}

	@Test
	void fiveTimesTheRateFor10SecondsKicks() {
		RelayGuard g = this.guard();
		// hstate: 10/s; 51 messages a second is over five times that
		String kick = null;
		int seconds = 0;
		while (kick == null && seconds < 20) {
			for (int i = 0; i < 51 && kick == null; i++) {
				kick = g.check(true, "hstate", "{\"t\":\"hstate\"}", 0, 0, NONE_ISSUED).kick();
			}

			if (kick == null) {
				this.now.addAndGet(1000);
				seconds++;
			}
		}

		assertEquals(RelayGuard.KICK, kick);
		assertEquals(10, seconds, "kicked at the start of the 11th second");
	}

	@Test
	void fiveTimesTheRateOnlyNowAndThenDoesNotKick() {
		RelayGuard g = this.guard();
		for (int round = 0; round < 30; round++) {
			int n = round % 3 == 2 ? 10 : 51; // every third second is calm
			for (int i = 0; i < n; i++) {
				assertNull(g.check(true, "hstate", "{\"t\":\"hstate\"}", 0, 0, NONE_ISSUED).kick());
			}

			this.now.addAndGet(1000);
		}

		// an idle second breaks the run as well
		RelayGuard h = this.guard();
		for (int round = 0; round < 30; round++) {
			for (int i = 0; i < 51; i++) {
				assertNull(h.check(true, "hstate", "{\"t\":\"hstate\"}", 0, 0, NONE_ISSUED).kick());
			}

			this.now.addAndGet(round % 5 == 4 ? 2000 : 1000);
		}
	}

	@Test
	void exactlyFiveTimesTheRateIsNotOver() {
		RelayGuard g = this.guard();
		for (int round = 0; round < 15; round++) {
			for (int i = 0; i < 50; i++) {
				assertNull(g.check(true, "hstate", "{\"t\":\"hstate\"}", 0, 0, NONE_ISSUED).kick());
			}

			this.now.addAndGet(1000);
		}
	}

	@Test
	void theLogComesEvery5sAndRestarts() {
		RelayGuard g = this.guard();
		g.check(true, "cmd", "{\"t\":\"cmd\"}", 0, 0, NONE_ISSUED);
		this.now.addAndGet(4999);
		assertTrue(g.drainLog("CbHost1").isEmpty());
		this.now.addAndGet(1);
		assertEquals(List.of("relay: CbHost1 dropped cmd x 1 (type)"), g.drainLog("CbHost1"));
		this.now.addAndGet(5000);
		assertTrue(g.drainLog("CbHost1").isEmpty(), "nothing new");
	}

	@Test
	void anUnknownTypeIsLoggedAsOnePlainLine() {
		RelayGuard g = this.guard();
		g.check(true, "a\nb", "{}", 0, 0, NONE_ISSUED);
		this.now.addAndGet(5000);
		assertEquals(List.of("relay: CbHost1 dropped a?b x 1 (type)"), g.drainLog("CbHost1"));
	}

	/** 10 000 ground messages at once, then a sustained flood: what passes stays within burst + rate x time, and it ends in a kick. */
	@Test
	void aFloodOf10000GroundStaysWithinTheBudget() {
		RelayGuard g = this.guard();
		int ok = 0;
		for (int i = 0; i < 10_000; i++) {
			RelayGuard.Verdict v = g.check(true, "ground", ground(i % 100, 0, 60, 64), 0, 0, NONE_ISSUED);
			assertNull(v.kick());
			ok += accepted(v) ? 1 : 0;
		}

		assertEquals(120, ok, "the burst");
		// a sustained flood of 1000 a second
		String kick = null;
		long start = this.now.get();
		for (int s = 0; s < 15 && kick == null; s++) {
			this.now.addAndGet(1000);
			for (int i = 0; i < 1000 && kick == null; i++) {
				RelayGuard.Verdict v = g.check(true, "ground", ground(i % 100, 0, 60, 64), 0, 0, NONE_ISSUED);
				kick = v.kick();
				ok += accepted(v) ? 1 : 0;
			}
		}

		double seconds = (this.now.get() - start) / 1000.0;
		assertTrue(ok <= 120 + 40 * seconds + 1, ok + " accepted in " + seconds + " s");
		assertEquals(RelayGuard.KICK, kick, "the flood ends in the overflow kick");
	}

	@Test
	void theFilteredMessageIsTheOneHandlersSee() {
		RelayGuard g = this.guard();
		JsonObject m = g.check(true, "pcells", "{\"t\":\"pcells\",\"c\":[1,64,1,900,64,0]}", 0, 0, NONE_ISSUED).message();
		assertEquals(JsonParser.parseString("{\"t\":\"pcells\",\"c\":[1,64,1]}"), m);
	}
}
