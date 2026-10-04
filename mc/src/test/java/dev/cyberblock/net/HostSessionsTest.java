package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostSessionsTest {
	private final UUID host1 = new UUID(1, 1), host2 = new UUID(2, 2), guest = new UUID(3, 3);
	private final List<String> wire1 = new ArrayList<>(), wire2 = new ArrayList<>();

	@BeforeEach
	void fresh() {
		HostSessions.reset();
		Permissions.use(new HostsData());
	}

	@AfterEach
	void forget() {
		HostSessions.reset();
	}

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private HostSession grant1() {
		return HostSessions.grant(this.host1, "CbHost1", null, this.wire1::add);
	}

	private static void up(final HostSession s, final String message) {
		JsonObject m = json(message);
		assertTrue(HostSessions.receive(s, m.get("t").getAsString(), m), message);
	}

	@Test
	void lifecycleNoneLinkedActiveLinkedEnd() {
		List<String> events = new ArrayList<>();
		HostSessions.onStart(s -> events.add("start " + s.name()));
		HostSessions.onEnd(s -> events.add("end " + s.name()));
		HostSession s = this.grant1();
		assertEquals(HostSession.State.NONE, s.state());
		assertTrue(HostSessions.all().isEmpty(), "a session without a hello is not listed");
		assertTrue(HostSessions.isHost(this.host1));
		up(s, "{'t':'hello','proto':1,'ver':'1.0.0'}");
		assertEquals(HostSession.State.LINKED, s.state());
		assertEquals(List.of(s), List.copyOf(HostSessions.all()));
		assertTrue(HostSessions.active().isEmpty());
		up(s, "{'t':'hstate','active':true,'vehicle':false,'air':false,'cr':true,'held':false,'fly':false,'launch':false,'seat':false,'lv':12,'feetCpZ':7.25}");
		assertEquals(HostSession.State.ACTIVE, s.state());
		assertTrue(s.active());
		assertTrue(s.crouching());
		assertEquals(12, s.levelK());
		assertEquals(7.25, s.feetCpZ());
		assertTrue(s.stands());
		up(s, "{'t':'hstate','active':false}");
		assertEquals(HostSession.State.LINKED, s.state());
		assertTrue(Double.isNaN(s.feetCpZ()), "an absent feetCpZ is unknown");
		HostSessions.end(this.host1, "disconnect");
		HostSessions.end(this.host1, "disconnect"); // a second end is nothing
		assertNull(HostSessions.of(this.host1));
		assertEquals(List.of("start CbHost1", "end CbHost1"), events, "start after the hello, end exactly once");
	}

	@Test
	void eachHelloIsAResyncPoint() {
		List<String> starts = new ArrayList<>();
		HostSessions.onStart(s -> starts.add(s.state().name()));
		HostSession s = this.grant1();
		up(s, "{'t':'hello','proto':1}");
		up(s, "{'t':'hstate','active':true}");
		up(s, "{'t':'hello','proto':1}");
		assertEquals(List.of("LINKED", "ACTIVE"), starts);
		assertEquals(HostSession.State.ACTIVE, s.state(), "a hello does not end the activity");
	}

	@Test
	void anActiveFlagBeforeTheHelloAppliesAtTheHello() {
		HostSession s = this.grant1();
		up(s, "{'t':'state','active':true,'vehicle':true}");
		assertEquals(HostSession.State.NONE, s.state());
		assertTrue(s.vehicle());
		up(s, "{'t':'hello','proto':1}");
		assertEquals(HostSession.State.ACTIVE, s.state());
	}

	@Test
	void aHelloOfAnotherProtocolIsRefused() {
		HostSession s = this.grant1();
		assertFalse(HostSessions.receive(s, "hello", json("{'t':'hello','proto':2}")));
		assertEquals(HostSession.State.NONE, s.state());
	}

	@Test
	void badBuiltinMessagesChangeNothingAndReachNoListener() {
		List<String> got = new ArrayList<>();
		HostSessions.listen("hstate", (s, m) -> got.add("hstate"));
		HostSessions.listen("stats", (s, m) -> got.add("stats"));
		HostSession s = this.grant1();
		up(s, "{'t':'hello','proto':1}");
		assertFalse(HostSessions.receive(s, "hstate", json("{'t':'hstate','active':'yes'}")));
		assertFalse(HostSessions.receive(s, "hstate", json("{'t':'hstate','active':true,'lv':64}")));
		assertFalse(HostSessions.receive(s, "stats", json("{'t':'stats','money':-1,'wanted':0,'district':'x','time':[1,2]}")));
		assertFalse(HostSessions.receive(s, "stats", json("{'t':'stats','money':5,'wanted':6,'district':'x','time':[1,2]}")));
		assertFalse(HostSessions.receive(s, "aim", json("{'t':'aim','d':65}")));
		assertEquals(HostSession.State.LINKED, s.state());
		assertTrue(got.isEmpty());
		up(s, "{'t':'stats','money':12450,'wanted':2,'district':'Watson','time':[14,35],'speed':-1}");
		assertEquals(12450, s.money());
		assertEquals(2, s.wanted());
		assertEquals("Watson", s.district());
		assertArrayEquals(new int[] {14, 35}, s.time());
		up(s, "{'t':'aim','d':2.5}");
		assertEquals(2.5, s.aim());
		up(s, "{'t':'aim','d':-0.5}");
		assertEquals(-1.0, s.aim());
		assertEquals(List.of("stats"), got);
	}

	@Test
	void listenersRunInOrderAndAThrowingOneIsContained() {
		List<String> got = new ArrayList<>();
		HostSessions.listen("npcs", (s, m) -> got.add("first"));
		HostSessions.listen("npcs", (s, m) -> {
			throw new IllegalStateException("boom");
		});
		HostSessions.listen("npcs", (s, m) -> got.add("third " + s.name()));
		HostSessions.listen("ground", (s, m) -> got.add("ground"));
		HostSession s = this.grant1();
		up(s, "{'t':'npcs','list':[]}");
		assertEquals(List.of("first", "third CbHost1"), got);
	}

	@Test
	void downGoesOutOnlyWhenLinkedAndInOrder() {
		HostSession s = this.grant1();
		s.send("{\"t\":\"align\",\"k\":3}");
		s.flush();
		assertTrue(this.wire1.isEmpty(), "nothing goes DOWN before the hello");
		up(s, "{'t':'hello','proto':1}");
		// re-test 2 rule: align immediately followed by blocks reset:true in one session queue
		s.send("{\"t\":\"align\",\"k\":3}");
		s.send("{\"t\":\"blocks\",\"reset\":true,\"c\":[]}");
		s.flush();
		assertEquals(List.of("{\"t\":\"align\",\"k\":3}", "{\"t\":\"blocks\",\"reset\":true,\"c\":[]}"), this.wire1);
	}

	/** Megaplan 9-F1: a burst goes out over several ticks, in order, and the minute's stats say so. */
	@Test
	void aDownBurstSpreadsOverTicks() {
		HostSession s = this.grant1();
		up(s, "{'t':'hello','proto':1}");
		int n = HostSessions.DOWN_PER_TICK + 10;
		for (int i = 0; i < n; i++) {
			s.send("{\"t\":\"hit\",\"n\":" + i + "}");
		}

		s.flush();
		assertEquals(HostSessions.DOWN_PER_TICK, this.wire1.size());
		s.flush();
		assertEquals(n, this.wire1.size());
		assertEquals("{\"t\":\"hit\",\"n\":" + (n - 1) + "}", this.wire1.get(n - 1));
		assertArrayEquals(new long[] {10, HostSessions.DOWN_PER_TICK}, HostSessions.takeDownStats());
		assertArrayEquals(new long[] {0, 0}, HostSessions.takeDownStats());
	}

	@Test
	void eachHostGetsOnlyItsOwnDown() {
		HostSession a = this.grant1();
		HostSession b = HostSessions.grant(this.host2, "CbHost2", null, this.wire2::add);
		up(a, "{'t':'hello','proto':1}");
		up(b, "{'t':'hello','proto':1}");
		a.send("{\"t\":\"hit\",\"target\":\"7\"}");
		b.send("{\"t\":\"hit\",\"target\":\"7\"}");
		b.send("{\"t\":\"hit\",\"target\":\"8\"}");
		HostSessions.flushAll();
		assertEquals(1, this.wire1.size());
		assertEquals(2, this.wire2.size());
	}

	@Test
	void primaryIsTheEarliestActiveSession() {
		HostSession a = this.grant1();
		HostSession b = HostSessions.grant(this.host2, "CbHost2", null, this.wire2::add);
		up(a, "{'t':'hello','proto':1}");
		up(b, "{'t':'hello','proto':1}");
		assertNull(HostSessions.primary());
		up(b, "{'t':'hstate','active':true}");
		up(a, "{'t':'hstate','active':true}");
		assertSame(b, HostSessions.primary());
		up(b, "{'t':'hstate','active':false}");
		assertSame(a, HostSessions.primary());
		up(b, "{'t':'hstate','active':true}");
		assertSame(a, HostSessions.primary(), "b's new activation is later than a's");
		assertEquals(2, HostSessions.active().size());
		assertEquals(1, HostSessions.hostsOtherThan(this.host1));
		assertEquals(2, HostSessions.hostsOtherThan(this.guest));
	}

	@Test
	void requestIdsRouteToTheAskerAndExpireAfter256() {
		HostSession s = this.grant1();
		int first = s.newRequest(this.guest);
		assertEquals(this.guest, s.asker(first));
		assertTrue(s.requests().issued(first));
		int last = first;
		for (int i = 0; i < RequestIds.KEEP - 1; i++) {
			last = s.newRequest(this.host1);
		}

		assertEquals(this.guest, s.asker(first), "the 256th newest is still remembered");
		s.newRequest(this.host1);
		assertNull(s.asker(first), "the 257th newest has expired");
		assertEquals(this.host1, s.asker(last));
		assertNull(s.asker(0));
		assertNull(s.asker(-5));
		assertNull(s.asker(last + 1000), "never issued");
	}

	@Test
	void requestIdsWrapPositive() {
		RequestIds ids = new RequestIds();
		for (int i = 0; i < 3; i++) {
			assertTrue(ids.issue(this.guest) > 0);
		}
	}

	@Test
	void endForgetsTheSessionsRequests() {
		HostSession s = this.grant1();
		int id = s.newRequest(this.guest);
		HostSessions.end(this.host1, "role revoked");
		HostSession again = this.grant1();
		assertNotSame(s, again);
		assertNull(again.asker(id));
	}

	@Test
	void endAllEndsEverySessionOnce() {
		List<String> ended = new ArrayList<>();
		HostSessions.onEnd(s -> ended.add(s.name()));
		this.grant1();
		HostSessions.grant(this.host2, "CbHost2", null, this.wire2::add);
		HostSessions.endAll("server stop");
		HostSessions.endAll("server stop");
		assertEquals(2, ended.size());
		assertTrue(HostSessions.sessionsInAnyState().isEmpty());
	}

	@Test
	void endRecordsTheLastSessionTime() {
		HostsData data = new HostsData();
		Permissions.use(data);
		this.grant1();
		HostSessions.end(this.host1, "disconnect");
		assertTrue(data.lastSession(this.host1) > 0);
	}

	@Test
	void grantIsIdempotent() {
		HostSession s = this.grant1();
		assertSame(s, this.grant1());
	}

	@Test
	void rosterIsDirtyAfterStartAndEnd() {
		HostSessions.takeRosterDirty();
		HostSession s = this.grant1();
		up(s, "{'t':'hello','proto':1}");
		assertTrue(HostSessions.takeRosterDirty());
		assertFalse(HostSessions.takeRosterDirty());
		HostSessions.end(this.host1, "disconnect");
		assertTrue(HostSessions.takeRosterDirty());
	}
}
